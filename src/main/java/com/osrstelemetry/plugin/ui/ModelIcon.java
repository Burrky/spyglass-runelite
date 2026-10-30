package com.osrstelemetry.plugin.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * Draws a game model (vertices, triangles and the game's own face colours) into a small picture, as the game draws item
 * pictures: turned and tilted to show its top and front, flat-shaded from above, fitted to the frame, with a dark one-pixel
 * outline (pass 31: compost bins, whose objects have no item picture). Pure: the client's model data is copied in on the
 * client thread; the result is cached by the caller and never redrawn for the same look.
 */
final class ModelIcon
{
	/** Turn about the vertical axis and the downward tilt, radians (the bins' open side and lid towards the viewer). */
	static final double YAW = Math.toRadians(-30);
	static final double PITCH = Math.toRadians(45);

	private ModelIcon()
	{
	}

	/**
	 * @param x            vertex x (east), y (down: the game's models grow upwards as y falls), z
	 * @param a            each face's first, second and third vertex
	 * @param colours      each face's colour, the game's packed HSL (6 bits hue, 3 saturation, 7 lightness)
	 * @param transparency each face's transparency (0 opaque … 255 invisible), or null
	 * @param ambient      the object's extra ambient light (its definition's {@code ambient})
	 */
	static BufferedImage render(float[] x, float[] y, float[] z, int[] a, int[] b, int[] c, short[] colours, byte[] transparency, int ambient,
		int width, int height)
	{
		int n = x.length;
		double[] px = new double[n];
		double[] py = new double[n];
		double[] pz = new double[n];
		double cy = Math.cos(YAW);
		double sy = Math.sin(YAW);
		double cp = Math.cos(PITCH);
		double sp = Math.sin(PITCH);
		for (int i = 0; i < n; i++)
		{
			double rx = x[i] * cy - z[i] * sy;
			double rz = x[i] * sy + z[i] * cy;
			// Tilted so the top shows: up (negative y) leans away from the viewer.
			double ry = y[i] * cp - rz * sp;
			double depth = y[i] * sp + rz * cp;
			px[i] = rx;
			py[i] = ry;
			pz[i] = depth;
		}
		double minX = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		int faces = a.length;
		boolean[] shown = new boolean[faces];
		for (int f = 0; f < faces; f++)
		{
			shown[f] = colours[f] != -1 && (transparency == null || (transparency[f] & 0xFF) < 200);
			if (!shown[f])
			{
				continue;
			}
			for (int v : new int[]{a[f], b[f], c[f]})
			{
				minX = Math.min(minX, px[v]);
				maxX = Math.max(maxX, px[v]);
				minY = Math.min(minY, py[v]);
				maxY = Math.max(maxY, py[v]);
			}
		}
		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		if (minX > maxX)
		{
			return img;
		}
		double scale = Math.min((width - 4) / Math.max(1, maxX - minX), (height - 4) / Math.max(1, maxY - minY));
		double ox = (width - (maxX - minX) * scale) / 2 - minX * scale;
		double oy = (height - (maxY - minY) * scale) / 2 - minY * scale;
		Integer[] order = new Integer[faces];
		double[] depth = new double[faces];
		for (int f = 0; f < faces; f++)
		{
			order[f] = f;
			depth[f] = (pz[a[f]] + pz[b[f]] + pz[c[f]]) / 3;
		}
		// Far faces first (larger depth is further away).
		Arrays.sort(order, (p, q) -> Double.compare(depth[q], depth[p]));
		// Light from above, over the viewer's left shoulder.
		double lx = -0.45;
		double ly = -0.85;
		double lz = -0.35;
		double ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
		lx /= ll;
		ly /= ll;
		lz /= ll;
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		for (int f : order)
		{
			if (!shown[f])
			{
				continue;
			}
			int i = a[f];
			int j = b[f];
			int k = c[f];
			double ux = px[j] - px[i];
			double uy = py[j] - py[i];
			double uz = pz[j] - pz[i];
			double vx = px[k] - px[i];
			double vy = py[k] - py[i];
			double vz = pz[k] - pz[i];
			double nx = uy * vz - uz * vy;
			double ny = uz * vx - ux * vz;
			double nz = ux * vy - uy * vx;
			double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
			double lambert = nl == 0 ? 0 : Math.abs((nx * lx + ny * ly + nz * lz) / nl);
			double light = Math.min(1.25, 0.5 + ambient / 128.0 + 0.62 * lambert);
			Color base = new Color(hslToRgb(colours[f] & 0xFFFF));
			Color shaded = new Color(clamp(base.getRed() * light), clamp(base.getGreen() * light), clamp(base.getBlue() * light));
			g.setColor(shaded);
			Polygon p = new Polygon(
				new int[]{(int) Math.round(px[i] * scale + ox), (int) Math.round(px[j] * scale + ox), (int) Math.round(px[k] * scale + ox)},
				new int[]{(int) Math.round(py[i] * scale + oy), (int) Math.round(py[j] * scale + oy), (int) Math.round(py[k] * scale + oy)}, 3);
			g.fillPolygon(p);
			g.drawPolygon(p); // no hairline gaps between neighbouring faces
		}
		g.dispose();
		outline(img);
		return img;
	}

	private static int clamp(double v)
	{
		return (int) Math.max(0, Math.min(255, Math.round(v)));
	}

	/** A dark one-pixel edge around the shape, as the game's item pictures have. */
	private static void outline(BufferedImage img)
	{
		int w = img.getWidth();
		int h = img.getHeight();
		int[] px = img.getRGB(0, 0, w, h, null, 0, w);
		int[] out = px.clone();
		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				if ((px[y * w + x] >>> 24) != 0)
				{
					continue;
				}
				boolean edge = x > 0 && (px[y * w + x - 1] >>> 24) != 0 || x < w - 1 && (px[y * w + x + 1] >>> 24) != 0
					|| y > 0 && (px[(y - 1) * w + x] >>> 24) != 0 || y < h - 1 && (px[(y + 1) * w + x] >>> 24) != 0;
				if (edge)
				{
					out[y * w + x] = 0xFF0E0E0E;
				}
			}
		}
		img.setRGB(0, 0, w, h, out, 0, w);
	}

	/** The game's packed HSL to RGB (the client's palette formula, brightness 0.8). */
	static int hslToRgb(int hsl)
	{
		double hue = (hsl >> 10 & 63) / 64.0 + 0.0078125;
		double sat = (hsl >> 7 & 7) / 8.0 + 0.0625;
		double lum = (hsl & 127) / 128.0;
		double r = lum;
		double g = lum;
		double b = lum;
		if (sat != 0)
		{
			double q = lum < 0.5 ? lum * (1 + sat) : lum + sat - lum * sat;
			double p = 2 * lum - q;
			r = hue(p, q, hue + 1 / 3.0);
			g = hue(p, q, hue);
			b = hue(p, q, hue - 1 / 3.0);
		}
		int ri = (int) (Math.pow(r, 0.8) * 256);
		int gi = (int) (Math.pow(g, 0.8) * 256);
		int bi = (int) (Math.pow(b, 0.8) * 256);
		return Math.min(255, ri) << 16 | Math.min(255, gi) << 8 | Math.min(255, bi);
	}

	private static double hue(double p, double q, double t)
	{
		if (t < 0)
		{
			t += 1;
		}
		if (t > 1)
		{
			t -= 1;
		}
		if (t < 1 / 6.0)
		{
			return p + (q - p) * 6 * t;
		}
		if (t < 0.5)
		{
			return q;
		}
		if (t < 2 / 3.0)
		{
			return p + (q - p) * (2 / 3.0 - t) * 6;
		}
		return p;
	}
}
