package com.realdiplad.colouredlighting.client.colour;

/** Tiny helpers for packed 0xRRGGBB colours. */
public final class ColourMath {
	private ColourMath() {}

	public static int pack(int r, int g, int b) {
		return (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
	}

	public static int r(int rgb) { return (rgb >> 16) & 0xFF; }
	public static int g(int rgb) { return (rgb >> 8) & 0xFF; }
	public static int b(int rgb) { return rgb & 0xFF; }

	public static int clamp255(int v) {
		return v < 0 ? 0 : Math.min(v, 255);
	}

	/** Scales the colour so its brightest channel is 255 (pure hue). Black becomes white. */
	public static int normaliseMax(int rgb) {
		int r = r(rgb), g = g(rgb), b = b(rgb);
		int max = Math.max(r, Math.max(g, b));
		if (max <= 0) return 0xFFFFFF;
		float s = 255f / max;
		return pack(Math.round(r * s), Math.round(g * s), Math.round(b * s));
	}

	/** Pushes channels away from their mean by factor k (k>1 = more saturated). */
	public static int boostSaturation(int rgb, float k) {
		float r = r(rgb), g = g(rgb), b = b(rgb);
		float mean = (r + g + b) / 3f;
		return pack(Math.round(mean + (r - mean) * k),
			Math.round(mean + (g - mean) * k),
			Math.round(mean + (b - mean) * k));
	}
}
