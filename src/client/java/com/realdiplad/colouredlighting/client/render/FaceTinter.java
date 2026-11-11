package com.realdiplad.colouredlighting.client.render;

import com.realdiplad.colouredlighting.client.colour.BlockColourCache;
import com.realdiplad.colouredlighting.client.colour.ColourMath;
import com.realdiplad.colouredlighting.client.colour.LightColourField;
import com.realdiplad.colouredlighting.client.config.ClConfig;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;

/**
 * Computes an RGB multiplier (each channel 0..1) for ONE vertex of a block face.
 *
 * Two ingredients:
 *
 *  1. Light hue - the colour of the block light reaching the vertex (LightColourField), sampled
 *     at the four cells that touch the vertex on the air side of the face (like smooth lighting).
 *  2. Colour bleed - solid blocks on the air side of the face reflect their own average colour
 *     (BlockColourCache) onto the surface. Sampled over a 5x5 disc around the face with a weight
 *     that falls off with the distance to the vertex, so tinting is strongest right next to a
 *     coloured block and fades smoothly - a cheap distance-falloff "radiosity" bounce.
 *
 * Both are baked into the chunk mesh (zero per-frame cost). The per-face cell data is fetched
 * once and shared by all four vertices of the quad; the bleed loop only walks the cells that
 * actually contain coloured blocks, so the common all-air case costs almost nothing per vertex.
 * One instance per thread; not thread-safe.
 */
public final class FaceTinter {
	/** Bleed sampling radius in blocks around the face centre (5x5 disc at radius 2). */
	private static final int DISC_RADIUS = 2;
	private static final int DISC_STRIDE = DISC_RADIUS * 2 + 1; // 5
	private static final int DISC_CELLS = DISC_STRIDE * DISC_STRIDE; // 25
	/** Bleed weight per block of Manhattan distance from the vertex. */
	private static final float BLEED_ATTENUATION = 0.55f;
	/** Weight-sum scale that maps "coloured wall next to the vertex" to ~full bounceStrength. */
	private static final float BLEED_WEIGHT_SCALE = 2.5f;
	/** Largest per-vertex Manhattan distance: (|du|+1) + (|dv|+1) <= 2*(DISC_RADIUS+1) = 6. */
	private static final int MAX_BLEED_DISTANCE = 2 * (DISC_RADIUS + 1);
	/** Precomputed attenuation^distance, indexed by Manhattan distance from the vertex. */
	private static final float[] BLEED_WEIGHTS = new float[MAX_BLEED_DISTANCE + 1];
	static {
		for (int i = 0; i < BLEED_WEIGHTS.length; i++) BLEED_WEIGHTS[i] = (float) Math.pow(BLEED_ATTENUATION, i);
	}

	private final BlockPos.Mutable scratchPos = new BlockPos.Mutable();

	// Per-face disc cache, refilled whenever the face (position + direction) changes. The four
	// vertices of a quad share the same face and therefore everything below.
	private final boolean[] discValid = new boolean[DISC_CELLS]; // inside the world snapshot?
	private final boolean[] discOpaque = new boolean[DISC_CELLS];
	// Compacted list of disc cells that actually bleed (opaque blocks), so the per-vertex loop
	// stays short even though the disc has 25 cells.
	private final int[] bleedDu = new int[DISC_CELLS];
	private final int[] bleedDv = new int[DISC_CELLS];
	private final int[] bleedHue = new int[DISC_CELLS];
	private int bleedCount;

	private long lastFaceKey;  // position of the face centre cell
	private int lastFaceFlags; // face offset + "wide" flag

	/** Disc index for offsets (du, dv), each in -DISC_RADIUS..DISC_RADIUS. */
	private static int discIndex(int du, int dv) {
		return (du + DISC_RADIUS) * DISC_STRIDE + (dv + DISC_RADIUS);
	}

	/**
	 * Fills the disc for one face. With bleed active all 25 cells are sampled; otherwise only
	 * the 3x3 centre is needed (the four vertices' light samples all land in there).
	 */
	private void ensureDisc(BlockRenderView world, int baseX, int baseY, int baseZ,
			int faceOffsetX, int faceOffsetY, int faceOffsetZ,
			int axisUx, int axisUy, int axisUz, int axisVx, int axisVy, int axisVz,
			int meshChunkX, int meshChunkZ, boolean wide) {
		int flags = (faceOffsetX + 1) | (faceOffsetY + 1) << 2 | (faceOffsetZ + 1) << 4 | (wide ? 1 << 6 : 0);
		long key = (baseX & 0x3FFFFFFL) | (baseY & 0xFFFL) << 26 | (baseZ & 0x3FFFFFFL) << 38;
		if (key == lastFaceKey && flags == lastFaceFlags) return; // same face: reuse everything
		lastFaceKey = key;
		lastFaceFlags = flags;

		bleedCount = 0;
		int radius = wide ? DISC_RADIUS : 1;
		for (int du = -radius; du <= radius; du++) {
			for (int dv = -radius; dv <= radius; dv++) {
				int idx = discIndex(du, dv);
				int cellX = baseX + du * axisUx + dv * axisVx;
				int cellY = baseY + du * axisUy + dv * axisVy;
				int cellZ = baseZ + du * axisUz + dv * axisVz;
				if (Math.abs((cellX >> 4) - meshChunkX) > 1 || Math.abs((cellZ >> 4) - meshChunkZ) > 1) {
					discValid[idx] = false; // outside the world snapshot: skip everywhere
					continue;
				}
				discValid[idx] = true;
				scratchPos.set(cellX, cellY, cellZ);
				BlockState state = world.getBlockState(scratchPos);
				boolean opaque = state.isOpaqueFullCube(world, scratchPos);
				discOpaque[idx] = opaque;
				if (wide && opaque) {
					bleedDu[bleedCount] = du;
					bleedDv[bleedCount] = dv;
					bleedHue[bleedCount] = BlockColourCache.albedoHue(state);
					bleedCount++;
				}
			}
		}
	}

	/**
	 * @param vertexX,vertexY,vertexZ vertex position inside its block (0..1)
	 * @param normalX,normalY,normalZ vertex normal
	 * @param packedLight packed lightmap coords (block << 4 | sky << 20)
	 */
	public void compute(BlockRenderView world, BlockPos pos, float vertexX, float vertexY, float vertexZ,
			float normalX, float normalY, float normalZ, int packedLight, ClConfig config) {
		redMultiplier = greenMultiplier = blueMultiplier = 1f;

		float blockLight = (packedLight >> 4) & 15;
		float skyLight = (packedLight >> 20) & 15;
		if (blockLight <= 0f && skyLight <= 0f) return; // pitch black: nothing to colour
		if (blockLight <= 0f && config.bounceStrength <= 0f) return; // daylight surface, bleed off

		// --- which neighbouring cell does this face look into? -----------------------------
		float normalAbsX = Math.abs(normalX), normalAbsY = Math.abs(normalY), normalAbsZ = Math.abs(normalZ);
		int faceOffsetX = 0, faceOffsetY = 0, faceOffsetZ = 0;
		if (normalAbsY > 0.7f && normalAbsY >= normalAbsX && normalAbsY >= normalAbsZ) faceOffsetY = normalY > 0 ? 1 : -1;
		else if (normalAbsX > 0.7f && normalAbsX >= normalAbsZ) faceOffsetX = normalX > 0 ? 1 : -1;
		else if (normalAbsZ > 0.7f) faceOffsetZ = normalZ > 0 ? 1 : -1;
		// diagonal quads (cross-shaped plants...) keep the offset at 0 and use their own cell

		// Unit vectors along the face's two tangent axes (U, V) and how far the vertex leans
		// along each: vertices below 0.5 lean towards -1 on that axis, above towards +1.
		int axisUx = 0, axisUy = 0, axisUz = 0, axisVx = 0, axisVy = 0, axisVz = 0;
		float vertexU, vertexV;
		if (faceOffsetX != 0)      { axisUy = 1; axisVz = 1; vertexU = vertexY; vertexV = vertexZ; }
		else if (faceOffsetZ != 0) { axisUx = 1; axisVy = 1; vertexU = vertexX; vertexV = vertexY; }
		else                       { axisUx = 1; axisVz = 1; vertexU = vertexX; vertexV = vertexZ; }
		int stepU = vertexU < 0.5f ? -1 : 1;
		int stepV = vertexV < 0.5f ? -1 : 1;

		int baseX = pos.getX() + faceOffsetX, baseY = pos.getY() + faceOffsetY, baseZ = pos.getZ() + faceOffsetZ;
		int meshChunkX = pos.getX() >> 4, meshChunkZ = pos.getZ() >> 4;

		boolean wide = config.bounceStrength > 0f;
		ensureDisc(world, baseX, baseY, baseZ, faceOffsetX, faceOffsetY, faceOffsetZ,
				axisUx, axisUy, axisUz, axisVx, axisVy, axisVz, meshChunkX, meshChunkZ, wide);

		// 1) coloured block light: sample the light field at the four cells touching the vertex
		float hueRed = 0, hueGreen = 0, hueBlue = 0, hueWeightSum = 0;
		if (blockLight > 0f && config.lightStrength > 0f) {
			for (int corner = 0; corner < 4; corner++) {
				int cornerOffsetU = (corner == 1 || corner == 3) ? stepU : 0;
				int cornerOffsetV = (corner >= 2) ? stepV : 0;
				int idx = discIndex(cornerOffsetU, cornerOffsetV);
				if (!discValid[idx] || discOpaque[idx]) continue; // outside snapshot / inside a solid
				int cellX = baseX + cornerOffsetU * axisUx + cornerOffsetV * axisVx;
				int cellY = baseY + cornerOffsetU * axisUy + cornerOffsetV * axisVy;
				int cellZ = baseZ + cornerOffsetU * axisUz + cornerOffsetV * axisVz;
				int lightSample = LightColourField.sample(world, meshChunkX, meshChunkZ, cellX, cellY, cellZ, scratchPos);
				if (lightSample != 0) {
					float sampleLevel = (lightSample >>> 24) & 15;
					hueRed += ColourMath.r(lightSample) * sampleLevel;
					hueGreen += ColourMath.g(lightSample) * sampleLevel;
					hueBlue += ColourMath.b(lightSample) * sampleLevel;
					hueWeightSum += sampleLevel;
				}
			}
		}

		float lightInfluence = blockLight / (blockLight + skyLight * config.skyLightWeight + 1e-3f) * config.lightStrength;
		if (hueWeightSum > 0f && lightInfluence > 0f) {
			float avgHueRed = hueRed / hueWeightSum / 255f, avgHueGreen = hueGreen / hueWeightSum / 255f,
					avgHueBlue = hueBlue / hueWeightSum / 255f;
			redMultiplier = 1f + (avgHueRed - 1f) * lightInfluence;
			greenMultiplier = 1f + (avgHueGreen - 1f) * lightInfluence;
			blueMultiplier = 1f + (avgHueBlue - 1f) * lightInfluence;
		}

		// 2) colour bleed: distance-weighted reflection from the coloured blocks in the disc.
		// Walks only the compacted list of opaque cells, so all-air faces cost nothing here.
		if (bleedCount > 0) {
			float bleedRed = 0, bleedGreen = 0, bleedBlue = 0, bleedWeightSum = 0;
			for (int i = 0; i < bleedCount; i++) {
				int distance = Math.abs(bleedDu[i] - stepU) + Math.abs(bleedDv[i] - stepV);
				float weight = BLEED_WEIGHTS[distance];
				int hue = bleedHue[i];
				bleedRed += ColourMath.r(hue) * weight;
				bleedGreen += ColourMath.g(hue) * weight;
				bleedBlue += ColourMath.b(hue) * weight;
				bleedWeightSum += weight;
			}
			if (bleedWeightSum > 0f) {
				float bleedInfluence = config.bounceStrength
						* Math.min(1f, bleedWeightSum / BLEED_WEIGHT_SCALE)
						* (Math.max(blockLight, skyLight) / 15f);
				float avgBleedRed = bleedRed / bleedWeightSum / 255f, avgBleedGreen = bleedGreen / bleedWeightSum / 255f,
						avgBleedBlue = bleedBlue / bleedWeightSum / 255f;
				redMultiplier *= 1f + (avgBleedRed - 1f) * bleedInfluence;
				greenMultiplier *= 1f + (avgBleedGreen - 1f) * bleedInfluence;
				blueMultiplier *= 1f + (avgBleedBlue - 1f) * bleedInfluence;
			}
		}

		float minChannel = config.minChannel;
		redMultiplier = redMultiplier < minChannel ? minChannel : Math.min(redMultiplier, 1f);
		greenMultiplier = greenMultiplier < minChannel ? minChannel : Math.min(greenMultiplier, 1f);
		blueMultiplier = blueMultiplier < minChannel ? minChannel : Math.min(blueMultiplier, 1f);
	}
}