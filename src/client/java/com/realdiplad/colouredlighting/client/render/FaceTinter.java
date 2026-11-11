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
 * It looks at the 5x5 cells on the air side of the face (radius=2 disc) and mixes two things:
 *
 *  1. Light hue  - the colour of the block light reaching the vertex (LightColourField),
 *                  weighted by how much of the vertex's lightmap value is block light vs sky light.
 *  2. Colour bleed from distance-weighted albedo of solid blocks within the disc:
 *                     a cheap distance-falloff "radiosity" that matches the reference look.
 *
 * The result multiplies the vertex colour, so it is baked into the chunk mesh - zero per-frame cost.
 * One instance per thread; not thread-safe.
 */
public final class FaceTinter {

	/** Maximum bleed sampling radius (in blocks) — cells farther away contribute negligibly. */
	private static final int BLEED_RADIUS = 2;
	/** Attenuation per block from the vertex: e.g. at r=1 weight=0.55, r=2 weight=0.30, etc. */
	private static final float BLEED_ATTENUATION = 0.55f;

	/** Normalisation scale for the weighted bleed sum (tuned so a single adjacent wall gives ≈bounce). */
	private static final float BLEED_WEIGHT_SCALE = 2.5f;

	/** Precomputed weight lookup for distances 0..6. */
	private static final float[] WEIGHTS = {
			1.0f, BLEED_ATTENUATION, (float) Math.pow(BLEED_ATTENUATION, 2.0f),
			(float) Math.pow(BLEED_ATTENUATION, 3.0f), (float) Math.pow(BLEED_ATTENUATION, 4.0f),
			(float) Math.pow(BLEED_ATTENUATION, 5.0f), (float) Math.pow(BLEED_ATTENUATION, 6.0f)
	};

	private final BlockPos.Mutable scratchPos = new BlockPos.Mutable();

	// Direct-mapped disc cache (5×5 = 25 cells) for a given face, shared across all 4 vertices.
	// Every call to compute() refills if the face changed, otherwise reuses.
	private static final int CD_SIZE = 25; // (R+1)²
	private final boolean[] cdValid = new boolean[CD_SIZE]; // fit within snapshot limits?
	private final boolean[] cdOpaque = new boolean[CD_SIZE];
	private final int[] cdHue = new int[CD_SIZE];

	// Identity of the last face we filled these disc entries for. (pos is immutable per call.)
	private int lastPosX, lastPosY, lastPosZ;
	private int lastFaceOffsetX, lastFaceOffsetY, lastFaceOffsetZ;

	/** Final vertex colour multipliers, read back by the caller after {@link #compute}. */
	public float redMultiplier = 1f, greenMultiplier = 1f, blueMultiplier = 1f;

	/**
	 * Ensures the disc cache for the current face is up-to-date.
	 *
	 * @param world         world snapshot
	 * @param pos           mesh position
	 * @param faceOffsetX/Y/Z direction the face looks into
	 * @param axisUx/Y/Z    axis U vector components (non-zero only one axis per component)
	 * @param axisVx/Y/Z    axis V vector components (same rule)
	 * @param meshChunkX/Z  chunk X/Z for snapshot bounds check
	 */
	private void prepareDisc(BlockRenderView world, int posX, int posY, int posZ,
	                         int faceOffsetX, int faceOffsetY, int faceOffsetZ,
	                         int axisUx, int axisUy, int axisUz,
	                         int axisVx, int axisVy, int axisVz,
	                         int meshChunkX, int meshChunkZ) {
		boolean sameFace = (posX == lastPosX && posY == lastPosY && posZ == lastPosZ &&
				faceOffsetX == lastFaceOffsetX &&
				faceOffsetY == lastFaceOffsetY &&
				faceOffsetZ == lastFaceOffsetZ);
		if (sameFace) return; // reuse cached disc

		lastPosX = posX; lastPosY = posY; lastPosZ = posZ;
		lastFaceOffsetX = faceOffsetX;
		lastFaceOffsetY = faceOffsetY;
		lastFaceOffsetZ = faceOffsetZ;

		// Fill the 5×5 disc. Index = (du+2)*5 + (dv+2), where du,dv ∈ [-2,2].
		for (int du = -BLEED_RADIUS; du <= BLEED_RADIUS; du++) {
			for (int dv = -BLEED_RADIUS; dv <= BLEED_RADIUS; dv++) {
				int idx = (du + BLEED_RADIUS + 1) * (BLEED_RADIUS + 1) + (dv + BLEED_RADIUS + 1); // 5*5 = 25
				int cx = posX + du * axisUx + dv * axisVx;
				int cy = posY + du * axisUy + dv * axisVy;
				int cz = posZ + du * axisUz + dv * axisVz;
				// Out of world snapshot bounds: mark invalid so we skip them later.
				if (Math.abs((cx >> 4) - meshChunkX) > 1 || Math.abs((cz >> 4) - meshChunkZ) > 1) {
					cdValid[idx] = false;
					continue;
				}
				cdValid[idx] = true;
				scratchPos.set(cx, cy, cz);
				BlockState state = world.getBlockState(scratchPos);
				cdOpaque[idx] = state.isOpaqueFullCube(world, scratchPos);
				cdHue[idx] = cdOpaque[idx] ? BlockColourCache.albedoHue(state) : -1;
			}
		}
	}

	/**
	 * Computes the tint for one vertex of a block face.
	 *
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
		if (blockLight <= 0f && config.bounceStrength <= 0f) return; // daylight surface, bleed disabled

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
		if (faceOffsetX != 0) { axisUy = 1; axisVz = 1; vertexU = vertexY; vertexV = vertexZ; }
		else if (faceOffsetZ != 0) { axisUx = 1; axisVy = 1; vertexU = vertexX; vertexV = vertexY; }
		else { axisUx = 1; axisVz = 1; vertexU = vertexX; vertexV = vertexZ; }
		int stepU = vertexU < 0.5f ? -1 : 1;
		int stepV = vertexV < 0.5f ? -1 : 1;

		int baseX = pos.getX() + faceOffsetX,
		    baseY = pos.getY() + faceOffsetY,
		    baseZ = pos.getZ() + faceOffsetZ;
		int meshChunkX = pos.getX() >> 4,
		    meshChunkZ = pos.getZ() >> 4;

		// Fill disc once per face; all vertices reuse it.
		prepareDisc(world, baseX, baseY, baseZ,
		            faceOffsetX, faceOffsetY, faceOffsetZ,
		            axisUx, axisUy, axisUz,
		            axisVx, axisVy, axisVz,
		            meshChunkX, meshChunkZ);

		// Light hue accumulator (weighted by level).
		float hueRed = 0, hueGreen = 0, hueBlue = 0, hueWeightSum = 0;

		// Colour bleed accumulator (distance-weighted).
		float bleedRed = 0, bleedGreen = 0, bleedBlue = 0, bleedWeightSum = 0;

		// Sample the 4 corner cells and the disc for bleed.
		for (int corner = 0; corner < 4; corner++) {
			int cornerOffsetU = (corner == 1 || corner == 3) ? stepU : 0;
			int cornerOffsetV = (corner >= 2) ? stepV : 0;
			int cellX = baseX + cornerOffsetU * axisUx + cornerOffsetV * axisVx;
			int cellY = baseY + cornerOffsetU * axisUy + cornerOffsetV * axisVy;
			int cellZ = baseZ + cornerOffsetU * axisUz + cornerOffsetV * axisVz;
			// Fast out-of-snapshot check using the disc index which already knows valid/invalid.
			int idx = (cornerOffsetU + BLEED_RADIUS + 1) * (BLEED_RADIUS + 1) + (cornerOffsetV + BLEED_RADIUS + 1);
			if (!cdValid[idx]) {
				// Cell outside world snapshot; no light to sample.
				if (blockLight > 0f) {
					// Light beyond snapshot: not reachable (or should be filtered out earlier).
				}
				continue;
			}

			// Skip solid blocks for light sampling.
			if (cdOpaque[idx]) {
				// Opaque cell contributes to colour bleed (distance-weighted albedo).
				int hue = cdHue[idx];
				int dist = Math.abs(cornerOffsetU - stepU) + Math.abs(cornerOffsetV - stepV);
				float w = WEIGHTS[dist];
				bleedRed += ColourMath.r(hue) * w;
				bleedGreen += ColourMath.g(hue) * w;
				bleedBlue += ColourMath.b(hue) * w;
				bleedWeightSum += w;
				continue;
			}

			// Non-opaque cell: sample light field.
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

		// 1) Coloured block light.
		float lightInfluence = blockLight / (blockLight + skyLight * config.skyLightWeight + 1e-3f) * config.lightStrength;
		if (hueWeightSum > 0f && lightInfluence > 0f) {
			float avgHueRed = hueRed / hueWeightSum / 255f,
			      avgHueGreen = hueGreen / hueWeightSum / 255f,
			      avgHueBlue = hueBlue / hueWeightSum / 255f;
			redMultiplier = 1f + (avgHueRed - 1f) * lightInfluence;
			greenMultiplier = 1f + (avgHueGreen - 1f) * lightInfluence;
			blueMultiplier = 1f + (avgHueBlue - 1f) * lightInfluence;
		}

		// 2) Distance-weighted colour bleed.
		if (bleedWeightSum > 0f) {
			float bleedInfluence = config.bounceStrength *
					   Math.min(1.0f, bleedWeightSum / BLEED_WEIGHT_SCALE) *
					   (Math.max(blockLight, skyLight) / 15.0f);
			float avgBleedRed = bleedRed / bleedWeightSum / 255f,
			      avgBleedGreen = bleedGreen / bleedWeightSum / 255f,
			      avgBleedBlue = bleedBlue / bleedWeightSum / 255f;
			redMultiplier *= 1f + (avgBleedRed - 1f) * bleedInfluence;
			greenMultiplier *= 1f + (avgBleedGreen - 1f) * bleedInfluence;
			blueMultiplier *= 1f + (avgBleedBlue - 1f) * bleedInfluence;
		}

		// Clamp low channels (options adjustable)
		float minChannel = config.minChannel;
		redMultiplier = redMultiplier < minChannel ? minChannel : Math.min(redMultiplier, 1f);
		greenMultiplier = greenMultiplier < minChannel ? minChannel : Math.min(greenMultiplier, 1f);
		blueMultiplier = blueMultiplier < minChannel ? minChannel : Math.min(blueMultiplier, 1f);
	}
}