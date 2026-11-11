package com.realdiplad.colouredlighting.client.mixin;

import com.realdiplad.colouredlighting.client.config.ClConfig;
import com.realdiplad.colouredlighting.client.render.TintingVertexConsumer;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.BlockModelRenderer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla chunk meshing calls BlockRenderManager#renderBlock once per block, with the chunk's
 * buffer as the VertexConsumer. We replace that single call with the identical vanilla call,
 * except the buffer is wrapped so every vertex gets its colour tint baked in.
 */
@Mixin(BlockRenderManager.class)
public abstract class BlockRenderManagerMixin {
	@Shadow
	public abstract BakedModel getModel(BlockState state);

	@Shadow
	public abstract BlockModelRenderer getModelRenderer();

	@Inject(method = "renderBlock", at = @At("HEAD"), cancellable = true)
	private void coloured_lighting$tintBlock(BlockState state, BlockPos pos, BlockRenderView world,
											 MatrixStack matrices, VertexConsumer vertexConsumer,
											 boolean cull, Random random, CallbackInfo ci) {
		ClConfig cfg = ClConfig.get();
		if (!cfg.enabled || state.getRenderType() != BlockRenderType.MODEL) {
			return; // let vanilla handle it
		}

		TintingVertexConsumer tinted = TintingVertexConsumer.wrap(vertexConsumer, world, pos, matrices.peek(), cfg);
		try {
			this.getModelRenderer().render(world, this.getModel(state), state, pos, matrices, tinted, cull,
				random, state.getRenderingSeed(pos), OverlayTexture.DEFAULT_UV);
		} finally {
			tinted.release();
		}
		ci.cancel();
	}
}
