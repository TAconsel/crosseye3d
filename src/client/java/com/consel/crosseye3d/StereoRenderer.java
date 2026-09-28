package com.consel.crosseye3d;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.HitResult;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Renders the level once per eye and packs both images side by side into the main render target.
 * <p>
 * The eye offset is baked into the projection matrix (view-space translation + off-axis shear) instead of moving
 * the camera. Chunk culling, entity extraction and camera position stay untouched, and shader packs that rebuild
 * view/world positions from {@code gbufferProjectionInverse} still get correct lighting for each eye.
 */
public final class StereoRenderer {
	public enum Eye {
		LEFT(-1.0F),
		RIGHT(1.0F);

		/** -1 for the left eye, +1 for the right eye. */
		public final float sign;

		Eye(final float sign) {
			this.sign = sign;
		}
	}

	private static final Vector4f BLACK = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);
	/** Samples a full-size eye image into one screen region; see side_by_side.vsh. */
	public static final RenderPipeline SIDE_BY_SIDE = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(CrossEye3DClient.MOD_ID, "pipeline/side_by_side"))
			.withVertexShader(Identifier.fromNamespaceAndPath(CrossEye3DClient.MOD_ID, "core/side_by_side"))
			.withFragmentShader("core/blit_screen")
			.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withCull(false)
			.build()
	);

	private static @Nullable Eye currentEye;
	private static boolean stereoThisFrame;
	/** m00 of the last unmodified level projection, used to place the per-eye crosshairs. */
	private static float lastProjectionM00 = 1.0F;

	private static final GpuTexture[] eyeTextures = new GpuTexture[2];
	private static final GpuTextureView[] eyeViews = new GpuTextureView[2];
	private static int eyeWidth;
	private static int eyeHeight;

	private StereoRenderer() {
	}

	public static boolean isRenderingEye() {
		return currentEye != null;
	}

	/** True while rendering an eye that another eye follows in the same frame. */
	public static boolean hasMoreEyes() {
		return currentEye != null && currentEye.ordinal() < Eye.values().length - 1;
	}

	/** Whether this frame's world was rendered in stereo (HUD elements follow this). */
	public static boolean isStereoFrame() {
		return stereoThisFrame;
	}

	private static float horizontalScale(final StereoConfig config) {
		// FULL: each eye is squeezed into half the width, so render it with half the aspect ratio.
		return config.layout == StereoConfig.Layout.FULL ? 2.0F : 1.0F;
	}

	/**
	 * Turns a normal perspective projection into the projection for the eye being rendered:
	 * {@code P' = Shear(k) * ScaleX(sx) * P * Translate(-sign * separation / 2)}.
	 */
	public static void applyEye(final Matrix4f projection) {
		Eye eye = currentEye;
		if (eye == null) {
			return;
		}

		StereoConfig config = CrossEye3DClient.config();
		float sx = horizontalScale(config);
		float halfSeparation = config.eyeSeparation * 0.5F;
		// Shift in NDC that brings a point at the convergence distance back to zero parallax.
		float shear = eye.sign * sx * projection.m00() * halfSeparation / config.convergence;
		Matrix4f eyeProjection = new Matrix4f()
			.translation(shear, 0.0F, 0.0F)
			.scale(sx, 1.0F, 1.0F)
			.mul(projection)
			.translate(-eye.sign * halfSeparation, 0.0F, 0.0F);
		projection.set(eyeProjection);
	}

	public static void rememberProjection(final Matrix4f projection) {
		lastProjectionM00 = projection.m00();
	}

	/**
	 * Replaces a single {@code renderLevel} call with one render per eye plus a side-by-side composite.
	 *
	 * @param renderEye renders the level (including anything that must be drawn per eye) into {@code mainTarget}
	 */
	public static void renderStereo(final RenderTarget mainTarget, final Vector4f clearColor, final Runnable renderEye) {
		stereoThisFrame = true;
		StereoConfig config = CrossEye3DClient.config();
		ensureEyeTextures(mainTarget.width, mainTarget.height);

		GpuDevice device = RenderSystem.getDevice();
		GpuTexture mainColor = mainTarget.getColorTexture();
		GpuTexture mainDepth = mainTarget.getDepthTexture();
		for (Eye eye : Eye.values()) {
			if (eye != Eye.LEFT) {
				device.createCommandEncoder().clearColorAndDepthTextures(mainColor, clearColor, mainDepth, 0.0);
				RendererCompat.beginSecondEye();
			}

			currentEye = eye;
			try {
				renderEye.run();
			} finally {
				currentEye = null;
			}

			// Only zero offsets here: the GL backend's copyTextureToTexture treats width/height as the far corner.
			device.createCommandEncoder().copyTextureToTexture(mainColor, eyeTextures[eye.ordinal()], 0, 0, 0, 0, 0, eyeWidth, eyeHeight);
		}

		// Cross-eye: the right eye looks at the left half of the screen, and vice versa.
		Eye leftHalf = config.crossEye ? Eye.RIGHT : Eye.LEFT;
		Eye rightHalf = config.crossEye ? Eye.LEFT : Eye.RIGHT;
		int letterbox = config.layout == StereoConfig.Layout.LETTERBOX ? 2 : 0;
		try (RenderPass pass = device.createCommandEncoder()
				.createRenderPass(() -> "CrossEye3D side-by-side", mainTarget.getColorTextureView(), Optional.of(BLACK))) {
			pass.setPipeline(SIDE_BY_SIDE);
			RenderSystem.bindDefaultUniforms(pass);
			drawRegion(pass, leftHalf, letterbox);
			drawRegion(pass, rightHalf, letterbox | 1);
		}
	}

	private static void drawRegion(final RenderPass pass, final Eye eye, final int region) {
		pass.bindTexture("InSampler", eyeViews[eye.ordinal()], RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
		pass.draw(6, 1, region * 6, 0);
	}

	public static void endFrame() {
		stereoThisFrame = false;
	}

	private static void ensureEyeTextures(final int width, final int height) {
		if (eyeTextures[0] != null && width == eyeWidth && height == eyeHeight) {
			return;
		}

		GpuDevice device = RenderSystem.getDevice();
		for (int i = 0; i < 2; i++) {
			if (eyeViews[i] != null) {
				eyeViews[i].close();
			}
			if (eyeTextures[i] != null) {
				eyeTextures[i].close();
			}
			String label = "CrossEye3D " + Eye.values()[i];
			eyeTextures[i] = device.createTexture(() -> label, 15, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
			eyeViews[i] = device.createTextureView(eyeTextures[i]);
		}
		eyeWidth = width;
		eyeHeight = height;
	}

	/**
	 * Horizontal GUI-space offset from the screen centre at which to draw the crosshair for {@code eye},
	 * including the parallax of whatever the player is looking at so the crosshair sits on the target in 3D.
	 */
	public static float crosshairOffset(final Eye eye, final int guiWidth) {
		StereoConfig config = CrossEye3DClient.config();
		Minecraft minecraft = Minecraft.getInstance();
		float inverseDistance = 0.0F;
		HitResult hit = minecraft.hitResult;
		if (hit != null && hit.getType() != HitResult.Type.MISS) {
			double distance = hit.getLocation().distanceTo(minecraft.gameRenderer.mainCamera().position());
			inverseDistance = 1.0F / (float)Math.max(distance, 0.3);
		}

		float ndc = eye.sign * horizontalScale(config) * lastProjectionM00 * config.eyeSeparation * 0.5F * (1.0F / config.convergence - inverseDistance);
		float quarter = guiWidth / 4.0F;
		boolean onLeftHalf = (eye == Eye.RIGHT) == config.crossEye;
		return (onLeftHalf ? -quarter : quarter) + ndc * quarter;
	}
}
