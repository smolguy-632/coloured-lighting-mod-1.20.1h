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
 * It looks at the 2x2 cells that touch the vertex on the air side of the face (like smooth
 * lighting does) and mixes two things:
 *
 *  1. Light hue  - the colour of the block light reaching the vertex (LightColourField),
 *                  weighted by how much of the vertex's lightmap value is block light vs sky light.
 *  2. Colour bleed - solid blocks standing in those cells reflect their own average colour
 *                  (BlockColourCache) onto the surface: a cheap one-bounce "radiosity".
 *
 * The result multiplies the vertex colour, so it is baked into the chunk mesh - zero per-frame cost.
 * One instance per thread; not thread-safe.
 */
public final class FaceTinter {
	private final BlockPos.Mutable scratchPos = new BlockPos.Mutable();

	// One-entry memo for the per-cell lookups below. Consecutive vertices of a quad (and faces of
	// the same block) touch the same cells, so the hit rate is high during chunk meshing.
	private long lastCellKey;
	private BlockState lastCellState;
	private boolean lastCellOpaque;
	private int lastCellHue = -1;

	/** Final vertex colour multipliers, read back by the caller after {@link #compute}. */
	public float redMultiplier = 1f, greenMultiplier = 1f, blueMultiplier = 1f;

	private BlockState cellState(BlockRenderView world, int cellX, int cellY, int cellZ) {
		long cellKey = (cellX & 0x3FFFFF) | ((long) (cellY & 0x3FFFFF) << 22) | ((long) (cellZ & 0x3FFFFF) << 44);
		if (cellKey != lastCellKey) {
			scratchPos.set(cellX, cellY, cellZ);
			lastCellState = world.getBlockState(scratchPos);
			lastCellOpaque = lastCellState.isOpaqueFullCube(world, scratchPos);
			lastCellHue = lastCellOpaque ? BlockColourCache.albedoHue(lastCellState) : -1;
			lastCellKey = cellKey;
		}
		return lastCellState;
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
		if (blockLight <= 0f && config.bounceStrength <= 0f) return; // daylight surface, bleed off: nothing to do

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

		float hueRed = 0, hueGreen = 0, hueBlue = 0, hueWeightSum = 0; // light hue accumulator (weighted by level)
		float bleedRed = 0, bleedGreen = 0, bleedBlue = 0;            // bleed accumulator
		int bleedCellCount = 0;

		for (int corner = 0; corner < 4; corner++) {
			int cornerOffsetU = (corner == 1 || corner == 3) ? stepU : 0;
			int cornerOffsetV = (corner >= 2) ? stepV : 0;
			int cellX = baseX + cornerOffsetU * axisUx + cornerOffsetV * axisVx;
			int cellY = baseY + cornerOffsetU * axisUy + cornerOffsetV * axisVy;
			int cellZ = baseZ + cornerOffsetU * axisUz + cornerOffsetV * axisVz;
			if (Math.abs((cellX >> 4) - meshChunkX) > 1 || Math.abs((cellZ >> 4) - meshChunkZ) > 1) continue;

			cellState(world, cellX, cellY, cellZ);
			if (lastCellOpaque) {
				if (corner != 0 && config.bounceStrength > 0f) { // a wall next to the surface reflects its colour
					bleedRed += ColourMath.r(lastCellHue);
					bleedGreen += ColourMath.g(lastCellHue);
					bleedBlue += ColourMath.b(lastCellHue);
					bleedCellCount++;
				}
				continue;
			}
			if (blockLight > 0f) {
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

		// 1) coloured block light
		float lightInfluence = blockLight / (blockLight + skyLight * config.skyLightWeight + 1e-3f) * config.lightStrength;
		if (hueWeightSum > 0f && lightInfluence > 0f) {
			float avgHueRed = hueRed / hueWeightSum / 255f, avgHueGreen = hueGreen / hueWeightSum / 255f,
					avgHueBlue = hueBlue / hueWeightSum / 255f;
			redMultiplier = 1f + (avgHueRed - 1f) * lightInfluence;
			greenMultiplier = 1f + (avgHueGreen - 1f) * lightInfluence;
			blueMultiplier = 1f + (avgHueBlue - 1f) * lightInfluence;
		}

		// 2) colour bleed from neighbouring solid blocks
		if (bleedCellCount > 0) {
			float bleedInfluence = config.bounceStrength * (bleedCellCount / 3f) * (Math.max(blockLight, skyLight) / 15f);
			float avgBleedRed = bleedRed / bleedCellCount / 255f, avgBleedGreen = bleedGreen / bleedCellCount / 255f,
					avgBleedBlue = bleedBlue / bleedCellCount / 255f;
			redMultiplier *= 1f + (avgBleedRed - 1f) * bleedInfluence;
			greenMultiplier *= 1f + (avgBleedGreen - 1f) * bleedInfluence;
			blueMultiplier *= 1f + (avgBleedBlue - 1f) * bleedInfluence;
		}

		float minChannel = config.minChannel;
		redMultiplier = redMultiplier < minChannel ? minChannel : Math.min(redMultiplier, 1f);
		greenMultiplier = greenMultiplier < minChannel ? minChannel : Math.min(greenMultiplier, 1f);
		blueMultiplier = blueMultiplier < minChannel ? minChannel : Math.min(blueMultiplier, 1f);
	}
}
