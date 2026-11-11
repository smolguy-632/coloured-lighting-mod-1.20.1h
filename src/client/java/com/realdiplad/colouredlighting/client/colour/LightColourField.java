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
 * Entry layout:  bits 0-23 rgb | 24-27 level | 28 valid
 * An entry is only trusted if its level still equals the live level, so light changes are picked
 * up immediately. Emitter-colour changes (resource pack / config reload) clear the whole cache,
 * which the reload listeners handle explicitly.
 */
public final class LightColourField {
	private LightColourField() {}

	private static final int VALID = 1 << 28;
	private static final int[] DX = {1, -1, 0, 0, 0, 0};
	private static final int[] DY = {0, 0, 1, -1, 0, 0};
	private static final int[] DZ = {0, 0, 0, 0, 1, -1};

	private static final ConcurrentHashMap<Long, int[]> SECTIONS = new ConcurrentHashMap<>();

	public static void clear() {
		SECTIONS.clear();
	}

	public static int sectionCount() {
		return SECTIONS.size();
	}

	/**
	 * @param meshChunkX,meshChunkZ chunk coordinates of the block currently being meshed; cells
	 *                              more than one chunk away are outside the chunk-render
	 *                              snapshot and are ignored
	 * @param scratchPos            scratch position (overwritten)
	 * @return (blockLightLevel << 24) | 0xRRGGBB hue, or 0 if the cell has no block light
	 */
	public static int sample(BlockRenderView world, int meshChunkX, int meshChunkZ, int cellX, int cellY, int cellZ,
							 BlockPos.Mutable scratchPos) {
		if (Math.abs((cellX >> 4) - meshChunkX) > 1 || Math.abs((cellZ >> 4) - meshChunkZ) > 1) return 0;

		scratchPos.set(cellX, cellY, cellZ);
		int lightLevel = world.getLightLevel(LightType.BLOCK, scratchPos);
		if (lightLevel <= 0) return 0;

		int[] section = section(cellX, cellY, cellZ);
		int cellIndex = ((cellY & 15) << 8) | ((cellZ & 15) << 4) | (cellX & 15);
		int cachedEntry = section[cellIndex];
		if ((cachedEntry & VALID) != 0 && ((cachedEntry >>> 24) & 15) == lightLevel) {
			return cachedEntry & 0x0FFFFFFF;
		}

		BlockState state = world.getBlockState(scratchPos);
		int emitterLuminance = state.getLuminance();
		int colourRgb;
		if (emitterLuminance > 0 && lightLevel <= emitterLuminance) {
			colourRgb = BlockColourCache.emitterColour(state);
		} else {
			float hueRed = 0, hueGreen = 0, hueBlue = 0, neighbourWeightSum = 0;
			for (int i = 0; i < 6; i++) {
				int neighbourX = cellX + DX[i], neighbourY = cellY + DY[i], neighbourZ = cellZ + DZ[i];
				if (Math.abs((neighbourX >> 4) - meshChunkX) > 1 || Math.abs((neighbourZ >> 4) - meshChunkZ) > 1) continue;
				scratchPos.set(neighbourX, neighbourY, neighbourZ);
				int neighbourLevel = world.getLightLevel(LightType.BLOCK, scratchPos);
				if (neighbourLevel <= lightLevel) continue; // only look "upstream", towards the emitter
				int neighbourSample = sample(world, meshChunkX, meshChunkZ, neighbourX, neighbourY, neighbourZ, scratchPos);
				if (neighbourSample == 0) continue;
				float neighbourWeight = (neighbourLevel - lightLevel == 1) ? 1f : 0.35f;
				hueRed += ColourMath.r(neighbourSample) * neighbourWeight;
				hueGreen += ColourMath.g(neighbourSample) * neighbourWeight;
				hueBlue += ColourMath.b(neighbourSample) * neighbourWeight;
				neighbourWeightSum += neighbourWeight;
			}
			colourRgb = neighbourWeightSum > 0
				? ColourMath.normaliseMax(ColourMath.pack(
					Math.round(hueRed / neighbourWeightSum),
					Math.round(hueGreen / neighbourWeightSum),
					Math.round(hueBlue / neighbourWeightSum)))
				: ColourMath.normaliseMax(ClConfig.get().defaultRgb());
		}

		int result = (lightLevel << 24) | colourRgb;
		section[cellIndex] = result | VALID;
		return result;
	}

	private static int[] section(int cellX, int cellY, int cellZ) {
		if (SECTIONS.size() > ClConfig.get().cacheMaxSections) {
			SECTIONS.clear();
		}
		long sectionKey = ChunkSectionPos.asLong(cellX >> 4, cellY >> 4, cellZ >> 4);
		int[] sectionArray = SECTIONS.get(sectionKey);
		if (sectionArray == null) {
			sectionArray = new int[4096];
			int[] existing = SECTIONS.putIfAbsent(sectionKey, sectionArray);
			if (existing != null) sectionArray = existing;
		}
		return sectionArray;
	}
}
