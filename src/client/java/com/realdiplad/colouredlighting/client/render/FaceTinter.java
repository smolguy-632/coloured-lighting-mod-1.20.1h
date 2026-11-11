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
final class FaceTinter {
	private final BlockPos.Mutable m = new BlockPos.Mutable();
	float r = 1f, g = 1f, b = 1f;

	/**
	 * @param lx,ly,lz vertex position inside its block (0..1)
	 * @param nx,ny,nz vertex normal
	 * @param light    packed lightmap coords (block << 4 | sky << 20)
	 */
	void compute(BlockRenderView world, BlockPos pos, float lx, float ly, float lz,
				 float nx, float ny, float nz, int light, ClConfig cfg) {
		r = g = b = 1f;

		float bl = (light >> 4) & 15;
		float sl = (light >> 20) & 15;
		if (bl <= 0f && sl <= 0f) return; // pitch black: nothing to colour

		// --- which neighbouring cell does this face look into? -----------------------------
		float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
		int dx = 0, dy = 0, dz = 0;
		if (ay > 0.7f && ay >= ax && ay >= az) dy = ny > 0 ? 1 : -1;
		else if (ax > 0.7f && ax >= az) dx = nx > 0 ? 1 : -1;
		else if (az > 0.7f) dz = nz > 0 ? 1 : -1;
		// diagonal quads (cross-shaped plants...) keep d = 0 and use their own cell

		// tangent axes A, B of the face plane and which way the vertex leans along each
		int aX = 0, aY = 0, aZ = 0, bX = 0, bY = 0, bZ = 0;
		float la, lb;
		if (dx != 0)      { aY = 1; bZ = 1; la = ly; lb = lz; }
		else if (dz != 0) { aX = 1; bY = 1; la = lx; lb = ly; }
		else              { aX = 1; bZ = 1; la = lx; lb = lz; }
		int sa = la < 0.5f ? -1 : 1;
		int sb = lb < 0.5f ? -1 : 1;

		int baseX = pos.getX() + dx, baseY = pos.getY() + dy, baseZ = pos.getZ() + dz;
		int pcx = pos.getX() >> 4, pcz = pos.getZ() >> 4;

		float lr = 0, lg = 0, lbl = 0, lw = 0; // light hue accumulator (weighted by level)
		float br = 0, bg = 0, bb = 0;          // bleed accumulator
		int bn = 0;

		for (int i = 0; i < 4; i++) {
			int ia = (i == 1 || i == 3) ? sa : 0;
			int ib = (i >= 2) ? sb : 0;
			int cx = baseX + ia * aX + ib * bX;
			int cy = baseY + ia * aY + ib * bY;
			int cz = baseZ + ia * aZ + ib * bZ;
			if (Math.abs((cx >> 4) - pcx) > 1 || Math.abs((cz >> 4) - pcz) > 1) continue;

			m.set(cx, cy, cz);
			BlockState st = world.getBlockState(m);
			if (st.isOpaqueFullCube(world, m)) {
				if (i != 0 && cfg.bounceStrength > 0f) { // a wall next to the surface reflects its colour
					int hue = BlockColourCache.albedoHue(st);
					br += ColourMath.r(hue); bg += ColourMath.g(hue); bb += ColourMath.b(hue);
					bn++;
				}
				continue;
			}
			if (bl > 0f) {
				int s = LightColourField.sample(world, pcx, pcz, cx, cy, cz, m);
				if (s != 0) {
					float level = (s >>> 24) & 15;
					lr += ColourMath.r(s) * level;
					lg += ColourMath.g(s) * level;
					lbl += ColourMath.b(s) * level;
					lw += level;
				}
			}
		}

		// 1) coloured block light
		float wl = bl / (bl + sl * cfg.skyLightWeight + 1e-3f) * cfg.lightStrength;
		if (lw > 0f && wl > 0f) {
			float hr = lr / lw / 255f, hg = lg / lw / 255f, hb = lbl / lw / 255f;
			r = 1f + (hr - 1f) * wl;
			g = 1f + (hg - 1f) * wl;
			b = 1f + (hb - 1f) * wl;
		}

		// 2) colour bleed from neighbouring solid blocks
		if (bn > 0) {
			float k = cfg.bounceStrength * (bn / 3f) * (Math.max(bl, sl) / 15f);
			float hr = br / bn / 255f, hg = bg / bn / 255f, hb = bb / bn / 255f;
			r *= 1f + (hr - 1f) * k;
			g *= 1f + (hg - 1f) * k;
			b *= 1f + (hb - 1f) * k;
		}

		float min = cfg.minChannel;
		r = r < min ? min : Math.min(r, 1f);
		g = g < min ? min : Math.min(g, 1f);
		b = b < min ? min : Math.min(b, 1f);
	}
}
