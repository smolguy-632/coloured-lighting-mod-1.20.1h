package com.realdiplad.colouredlighting.client.colour;

import com.realdiplad.colouredlighting.client.config.ClConfig;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.LightType;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Gives every lit cell a light COLOUR without running a second light engine.
 *
 * Vanilla already computed the block-light level (0-15) of every cell. Because vanilla light is a
 * flood fill, every lit cell has at least one neighbour with a higher level, all the way back to
 * the emitter. So the colour of a cell is:
 *   - the emitter's colour if the cell is the emitter itself, otherwise
 *   - the weighted average of the colours of its brighter neighbours (recursively).
 * Where two lights meet the hues blend. Results are memoised per 16^3 section in int[4096] arrays.
 *
 * Entry layout:  bits 0-23 rgb | 24-27 level | 28 valid | 29-31 epoch
 * An entry is only trusted if its level still equals the live level and its epoch is current,
 * so light changes are picked up immediately and emitter-colour changes within ~10 seconds.
 */
public final class LightColourField {
	private LightColourField() {}

	private static final int VALID = 1 << 28;
	private static final int[] DX = {1, -1, 0, 0, 0, 0};
	private static final int[] DY = {0, 0, 1, -1, 0, 0};
	private static final int[] DZ = {0, 0, 0, 0, 1, -1};

	private static final ConcurrentHashMap<Long, int[]> SECTIONS = new ConcurrentHashMap<>();
	private static volatile int epoch = 0;

	public static void clear() {
		SECTIONS.clear();
	}

	public static void advanceEpoch() {
		epoch = (epoch + 1) & 7;
	}

	public static int sectionCount() {
		return SECTIONS.size();
	}

	/**
	 * @param pcx,pcz chunk coordinates of the block currently being meshed; cells more than one
	 *                chunk away are outside the chunk-render snapshot and are ignored
	 * @param m       scratch position (overwritten)
	 * @return (blockLightLevel << 24) | 0xRRGGBB hue, or 0 if the cell has no block light
	 */
	public static int sample(BlockRenderView world, int pcx, int pcz, int x, int y, int z, BlockPos.Mutable m) {
		if (Math.abs((x >> 4) - pcx) > 1 || Math.abs((z >> 4) - pcz) > 1) return 0;

		m.set(x, y, z);
		int level = world.getLightLevel(LightType.BLOCK, m);
		if (level <= 0) return 0;

		int[] section = section(x, y, z);
		int idx = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
		int e = section[idx];
		if ((e & VALID) != 0 && ((e >>> 29) & 7) == epoch && ((e >>> 24) & 15) == level) {
			return e & 0x0FFFFFFF;
		}

		BlockState state = world.getBlockState(m);
		int lum = state.getLuminance();
		int rgb;
		if (lum > 0 && level <= lum) {
			rgb = BlockColourCache.emitterColour(state);
		} else {
			float r = 0, g = 0, b = 0, wSum = 0;
			for (int i = 0; i < 6; i++) {
				int nx = x + DX[i], ny = y + DY[i], nz = z + DZ[i];
				if (Math.abs((nx >> 4) - pcx) > 1 || Math.abs((nz >> 4) - pcz) > 1) continue;
				m.set(nx, ny, nz);
				int nl = world.getLightLevel(LightType.BLOCK, m);
				if (nl <= level) continue; // only look "upstream", towards the emitter
				int s = sample(world, pcx, pcz, nx, ny, nz, m);
				if (s == 0) continue;
				float w = (nl - level == 1) ? 1f : 0.35f;
				r += ColourMath.r(s) * w;
				g += ColourMath.g(s) * w;
				b += ColourMath.b(s) * w;
				wSum += w;
			}
			rgb = wSum > 0
				? ColourMath.normaliseMax(ColourMath.pack(Math.round(r / wSum), Math.round(g / wSum), Math.round(b / wSum)))
				: ColourMath.normaliseMax(ClConfig.get().defaultRgb());
		}

		int out = (level << 24) | rgb;
		section[idx] = out | VALID | (epoch << 29);
		return out;
	}

	private static int[] section(int x, int y, int z) {
		if (SECTIONS.size() > ClConfig.get().cacheMaxSections) {
			SECTIONS.clear();
		}
		long key = ChunkSectionPos.asLong(x >> 4, y >> 4, z >> 4);
		int[] arr = SECTIONS.get(key);
		if (arr == null) {
			arr = new int[4096];
			int[] prev = SECTIONS.putIfAbsent(key, arr);
			if (prev != null) arr = prev;
		}
		return arr;
	}
}
