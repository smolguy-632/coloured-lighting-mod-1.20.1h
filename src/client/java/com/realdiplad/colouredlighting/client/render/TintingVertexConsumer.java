package com.realdiplad.colouredlighting.client.render;

import com.realdiplad.colouredlighting.ColouredLighting;
import com.realdiplad.colouredlighting.client.config.ClConfig;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;
import org.joml.Matrix4f;

/**
 * Wraps the chunk's buffer while ONE block is being meshed. Vanilla pushes every baked quad
 * through {@link VertexConsumer#vertex(float, float, float, float, float, float, float, float,
 * float, int, int, float, float, float)}; we intercept exactly that call, multiply the vertex
 * colour by the FaceTinter result and forward everything else untouched.
 *
 * One instance per thread (chunk meshing is multi-threaded); {@link #release()} drops references.
 */
public final class TintingVertexConsumer implements VertexConsumer {
	private static final ThreadLocal<TintingVertexConsumer> LOCAL = ThreadLocal.withInitial(TintingVertexConsumer::new);
	private static volatile boolean warned;

	private final FaceTinter tinter = new FaceTinter();
	private VertexConsumer delegate;
	private BlockRenderView world;
	private BlockPos pos;
	private ClConfig config;
	private float tx, ty, tz;

	private TintingVertexConsumer() {}

	public static TintingVertexConsumer wrap(VertexConsumer delegate, BlockRenderView world, BlockPos pos,
											 MatrixStack.Entry matrix, ClConfig config) {
		TintingVertexConsumer wrapped = LOCAL.get();
		wrapped.delegate = delegate;
		wrapped.world = world;
		wrapped.pos = pos;
		wrapped.config = config;
		Matrix4f positionMatrix = matrix.getPositionMatrix();
		wrapped.tx = positionMatrix.m30(); // block origin inside the buffer (chunk-local while meshing)
		wrapped.ty = positionMatrix.m31();
		wrapped.tz = positionMatrix.m32();
		return wrapped;
	}

	public void release() {
		delegate = null;
		world = null;
		pos = null;
		config = null;
	}

	// ---- the one call we care about ----------------------------------------------------------
	@Override
	public void vertex(float x, float y, float z, float red, float green, float blue, float alpha,
					   float u, float v, int overlay, int light, float nx, float ny, float nz) {
		try {
			tinter.compute(world, pos, x - tx, y - ty, z - tz, nx, ny, nz, light, config);
			red *= tinter.redMultiplier;
			green *= tinter.greenMultiplier;
			blue *= tinter.blueMultiplier;
		} catch (Throwable t) { // never let a tint bug take the chunk builder down
			if (!warned) {
				warned = true;
				ColouredLighting.LOGGER.error("Vertex tint failed; rendering untinted from now on for failing vertices", t);
			}
		}
		delegate.vertex(x, y, z, red, green, blue, alpha, u, v, overlay, light, nx, ny, nz);
	}

	// ---- plain delegation ---------------------------------------------------------------------
	@Override
	public VertexConsumer vertex(double x, double y, double z) {
		delegate.vertex(x, y, z);
		return this;
	}

	@Override
	public VertexConsumer color(int red, int green, int blue, int alpha) {
		delegate.color(red, green, blue, alpha);
		return this;
	}

	@Override
	public VertexConsumer texture(float u, float v) {
		delegate.texture(u, v);
		return this;
	}

	@Override
	public VertexConsumer overlay(int u, int v) {
		delegate.overlay(u, v);
		return this;
	}

	@Override
	public VertexConsumer light(int u, int v) {
		delegate.light(u, v);
		return this;
	}

	@Override
	public VertexConsumer normal(float x, float y, float z) {
		delegate.normal(x, y, z);
		return this;
	}

	@Override
	public void next() {
		delegate.next();
	}

	@Override
	public void fixedColor(int red, int green, int blue, int alpha) {
		delegate.fixedColor(red, green, blue, alpha);
	}

	@Override
	public void unfixColor() {
		delegate.unfixColor();
	}
}
