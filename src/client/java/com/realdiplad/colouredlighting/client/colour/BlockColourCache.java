package com.realdiplad.colouredlighting.client.colour;

import com.realdiplad.colouredlighting.ColouredLighting;
import com.realdiplad.colouredlighting.client.config.ClConfig;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent cache of "what colour is this block".
 *
 * Every texture is decoded and averaged exactly once (SPRITES), every block state is then
 * reduced to two numbers exactly once (MODELS):
 *   - average colour : alpha-weighted mean of its texture(s)  -> used as albedo for colour bleed
 *   - emissive colour: mean weighted towards bright, saturated pixels -> used as light colour
 *
 * Everything is safe to call from chunk-builder worker threads. Cleared on resource reload.
 */
public final class BlockColourCache {
	private BlockColourCache() {}

	/** texture id -> {average rgb, emissive rgb} */
	private static final ConcurrentHashMap<Identifier, int[]> SPRITES = new ConcurrentHashMap<>();
	/** block state -> {average rgb, emissive rgb}; NO_DATA if it could not be computed */
	private static final ConcurrentHashMap<BlockState, int[]> MODELS = new ConcurrentHashMap<>();
	/** block state -> normalised hue used for colour bleed (max channel = 255) */
	private static final ConcurrentHashMap<BlockState, Integer> ALBEDO_HUE = new ConcurrentHashMap<>();
	/** block state -> light colour (max channel = 255) */
	private static final ConcurrentHashMap<BlockState, Integer> EMITTER = new ConcurrentHashMap<>();

	private static final int[] NO_DATA = new int[]{-1, -1};
	private static final int[] NO_SPRITE = new int[]{-1, -1};

	public static void clear() {
		SPRITES.clear();
		MODELS.clear();
		ALBEDO_HUE.clear();
		EMITTER.clear();
	}

	public static int size() {
		return MODELS.size();
	}

	public static int spriteCount() {
		return SPRITES.size();
	}

	/** Pure hue of the block's average colour (brightest channel = 255). Grey blocks give white. */
	public static int albedoHue(BlockState state) {
		Integer cached = ALBEDO_HUE.get(state);
		if (cached != null) return cached;
		int[] stats = stats(state);
		int hue = 0xFFFFFF;
		if (stats[0] >= 0) {
			int avg = stats[0];
			int max = Math.max(ColourMath.r(avg), Math.max(ColourMath.g(avg), ColourMath.b(avg)));
			if (max >= 24) hue = ColourMath.normaliseMax(avg); // very dark blocks don't tint
		}
		ALBEDO_HUE.put(state, hue);
		return hue;
	}

	/** Colour of the light a luminous block gives off (brightest channel = 255). */
	public static int emitterColour(BlockState state) {
		Integer cached = EMITTER.get(state);
		if (cached != null) return cached;
		ClConfig cfg = ClConfig.get();
		int result;
		String id = Registries.BLOCK.getId(state.getBlock()).toString();
		Integer override = cfg.override(id);
		if (override != null) {
			result = ColourMath.normaliseMax(override);
		} else {
			int[] stats = stats(state);
			if (stats[1] >= 0) {
				result = ColourMath.normaliseMax(ColourMath.boostSaturation(stats[1], cfg.saturationBoost));
			} else {
				result = ColourMath.normaliseMax(cfg.defaultRgb());
			}
		}
		EMITTER.put(state, result);
		return result;
	}

	// ------------------------------------------------------------------------------------

	private static int[] stats(BlockState state) {
		int[] cached = MODELS.get(state);
		if (cached != null) return cached;
		int[] computed;
		try {
			computed = computeModelStats(state);
		} catch (Throwable t) {
			ColouredLighting.LOGGER.debug("Could not derive colour for {}", state, t);
			computed = null;
		}
		if (computed == null) computed = NO_DATA;
		MODELS.put(state, computed);
		return computed;
	}

	private static int[] computeModelStats(BlockState state) {
		MinecraftClient mc = MinecraftClient.getInstance();
		BakedModel model = mc.getBlockRenderManager().getModel(state);
		Random random = Random.create(42L);

		long ar = 0, ag = 0, ab = 0, er = 0, eg = 0, eb = 0;
		int n = 0;

		for (int i = -1; i < 6; i++) {
			Direction dir = i < 0 ? null : Direction.values()[i];
			List<BakedQuad> quads = model.getQuads(state, dir, random);
			for (BakedQuad quad : quads) {
				Identifier spriteId = quad.getSprite().getContents().getId();
				int[] s = spriteStats(spriteId);
				if (s[0] < 0) continue;

				int avg = s[0];
				int emi = s[1];
				if (quad.hasColor()) { // biome/dye tinted quads: apply the default tint
					try {
						int tint = mc.getBlockColors().getColor(state, null, null, quad.getColorIndex());
						if (tint != -1) {
							avg = multiply(avg, tint);
							emi = multiply(emi, tint);
						}
					} catch (Throwable ignored) {
						// some colour providers need a world; keep the untinted colour
					}
				}
				ar += ColourMath.r(avg); ag += ColourMath.g(avg); ab += ColourMath.b(avg);
				er += ColourMath.r(emi); eg += ColourMath.g(emi); eb += ColourMath.b(emi);
				n++;
			}
		}
		if (n == 0) return null;
		return new int[]{
			ColourMath.pack((int) (ar / n), (int) (ag / n), (int) (ab / n)),
			ColourMath.pack((int) (er / n), (int) (eg / n), (int) (eb / n))
		};
	}

	private static int multiply(int a, int b) {
		return ColourMath.pack(
			ColourMath.r(a) * ColourMath.r(b) / 255,
			ColourMath.g(a) * ColourMath.g(b) / 255,
			ColourMath.b(a) * ColourMath.b(b) / 255);
	}

	/** Decodes a block texture once and returns {average rgb, emissive rgb} (or NO_SPRITE). */
	private static int[] spriteStats(Identifier spriteId) {
		int[] cached = SPRITES.get(spriteId);
		if (cached != null) return cached;

		int[] result = NO_SPRITE;
		Identifier tex = new Identifier(spriteId.getNamespace(), "textures/" + spriteId.getPath() + ".png");
		try {
			Optional<Resource> res = MinecraftClient.getInstance().getResourceManager().getResource(tex);
			if (res.isPresent()) {
				try (InputStream in = res.get().getInputStream(); NativeImage img = NativeImage.read(in)) {
					result = analyse(img);
				}
			}
		} catch (Throwable t) {
			ColouredLighting.LOGGER.debug("Could not read texture {}", tex, t);
		}
		SPRITES.put(spriteId, result);
		return result;
	}

	private static int[] analyse(NativeImage img) {
		int w = img.getWidth();
		int h = Math.min(img.getHeight(), w); // animated strips: first frame only

		double sr = 0, sg = 0, sb = 0, sw = 0;
		double er = 0, eg = 0, eb = 0, ew = 0;

		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int c = img.getColor(x, y); // ABGR
				int a = (c >>> 24) & 0xFF;
				if (a < 64) continue;
				int r = c & 0xFF, g = (c >> 8) & 0xFF, b = (c >> 16) & 0xFF;
				double wa = a / 255.0;

				sr += r * wa; sg += g * wa; sb += b * wa; sw += wa;

				double max = Math.max(r, Math.max(g, b)) / 255.0;
				double min = Math.min(r, Math.min(g, b)) / 255.0;
				double sat = max <= 0 ? 0 : (max - min) / max;
				double we = wa * max * max * max * (0.25 + sat); // bright + saturated pixels dominate
				er += r * we; eg += g * we; eb += b * we; ew += we;
			}
		}
		if (sw <= 0) return NO_SPRITE;
		int avg = ColourMath.pack((int) Math.round(sr / sw), (int) Math.round(sg / sw), (int) Math.round(sb / sw));
		int emi = ew > 0
			? ColourMath.pack((int) Math.round(er / ew), (int) Math.round(eg / ew), (int) Math.round(eb / ew))
			: avg;
		return new int[]{avg, emi};
	}
}
