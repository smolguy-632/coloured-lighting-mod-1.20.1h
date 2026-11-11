package com.realdiplad.colouredlighting.client.mixin.compat.sodium;

import com.realdiplad.colouredlighting.client.config.ClConfig;
import com.realdiplad.colouredlighting.client.render.FaceTinter;
import me.jellysquid.mods.sodium.client.model.light.data.QuadLightData;
import me.jellysquid.mods.sodium.client.model.quad.BakedQuadView;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderContext;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium's terrain mesher writes every quad through BlockRenderer#writeGeometry. Its colour array
 * holds one ABGR int per vertex: the low 24 bits are the RGB multiplier and the top byte is
 * overwritten with brightness afterwards (ColorABGR.withAlpha). Scaling the RGB here therefore
 * tints the vertex without disturbing Sodium's ambient occlusion.
 *
 * Only applied when Sodium is loaded (see ColouredLightingMixinPlugin).
 */
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer")
public abstract class SodiumBlockRendererMixin {

	/** One tinter per chunk-builder thread; FaceTinter keeps scratch state. */
	private static final ThreadLocal<FaceTinter> TINTER = ThreadLocal.withInitial(FaceTinter::new);

	@Inject(method = "writeGeometry", at = @At("HEAD"))
	private void coloured_lighting$tintQuad(BlockRenderContext ctx, ChunkModelBuilder builder, Vec3d offset,
											Material material, BakedQuadView quad, int[] colors,
											QuadLightData light, CallbackInfo ci) {
		ClConfig cfg = ClConfig.get();
		if (!cfg.enabled) return;

		float nx = 0f, ny = 0f, nz = 0f;
		ModelQuadFacing face = quad.getNormalFace();
		if (face == ModelQuadFacing.POS_X) nx = 1f;
		else if (face == ModelQuadFacing.NEG_X) nx = -1f;
		else if (face == ModelQuadFacing.POS_Y) ny = 1f;
		else if (face == ModelQuadFacing.NEG_Y) ny = -1f;
		else if (face == ModelQuadFacing.POS_Z) nz = 1f;
		else if (face == ModelQuadFacing.NEG_Z) nz = -1f;

		FaceTinter tinter = TINTER.get();
		for (int i = 0; i < 4; i++) {
			// Quad coordinates are block-local (0..1), the space FaceTinter expects.
			tinter.compute(ctx.world(), ctx.pos(), quad.getX(i), quad.getY(i), quad.getZ(i),
				nx, ny, nz, light.lm[i], cfg);

			int c = colors[i];
			int r = Math.round((c & 0xFF) * tinter.r);
			int g = Math.round(((c >> 8) & 0xFF) * tinter.g);
			int b = Math.round(((c >> 16) & 0xFF) * tinter.b);
			colors[i] = (c & 0xFF000000) | (clamp(b) << 16) | (clamp(g) << 8) | clamp(r);
		}
	}

	private static int clamp(int v) {
		return v < 0 ? 0 : Math.min(v, 255);
	}
}
